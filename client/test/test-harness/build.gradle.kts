plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// test-harness — фейки и сценарии без устройства (Plan.md §2.2, К4.6).
//
// Модуль тестовый, но исходники в commonMain: его потребители — тесты других
// модулей, а тестовые наборы никуда не публикуются.
kotlin {
    jvmToolchain(17)

    jvm()
    // Android-таргет здесь ради одного: первый признак готовности К4 требует, чтобы
    // сообщение уходило и приходило НА ANDROID против живого сервера. Проверить это
    // можно только на устройстве — сборка такого не видит.
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.core.coreOutbox)
            api(projects.core.coreDatabase)
            // Разбор кадров живого канала: сценарий приёма пишется на кадрах сервера.
            api(projects.core.coreNetwork)
            api(projects.core.coreEncryption)
            // Хранилище платформы: на устройстве проверяется весь стек, включая его.
            api(projects.core.coreSecrets)
            api(projects.domain.domainChat)
            // Признак готовности К4 сформулирован через Store — значит сценарий обязан
            // идти через него, а не мимо.
            api(projects.feature.featureChat)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            // Живой канал в прогоне по стенду: один сокет на устройство.
            implementation(libs.ktor.client.websockets)
            implementation(libs.sqldelight.driver.jvm)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.websockets)
            implementation(libs.sqldelight.driver.android)
        }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
    }
}

android {
    namespace = "io.tima.harness"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // Сквозной путь на устройстве идёт инструментальной проверкой: другого способа
        // прогнать наш стек на настоящем Android нет.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { abortOnError = false }
}

tasks.matching { it.name.startsWith("lint") }.configureEach { enabled = false }

// Хостовые unit-тесты Android здесь отключены осознанно, и это НЕ пропуск проверки.
//
// Они наследуют commonTest, то есть пытались бы прогнать те же сценарии, что уже идут
// на таргете jvm — тем же кодом и той же базой в памяти. Нового они не проверяют.
// А платформенное у Android проверяется там, где это вообще имеет смысл: на устройстве
// (androidInstrumentedTest) — настоящий драйвер базы, настоящий AndroidKeyStore,
// настоящая сеть.
//
// Технически они и не могут пройти: android-реализация драйвера требует Context, а на
// хосте его нет. Подсунуть ей поддельный значило бы проверять подделку.
tasks.matching { it.name.endsWith("UnitTest") }.configureEach { enabled = false }

// Прогон против живого стенда. НЕ часть сборки и НЕ часть CI: он создаёт настоящие
// аккаунты на настоящем сервере. Запускается руками и только по стенду, где включён
// TIMA_DEV_SMS — иначе код подтверждения пришлось бы получать настоящей SMS.
tasks.register<JavaExec>("standRun") {
    group = "проверка"
    description = "Сквозной путь против стенда: регистрация, отправка, приём"
    mainClass.set("io.tima.harness.StandRunKt")
    classpath = kotlin.targets.getByName("jvm").compilations.getByName("main").let {
        it.output.allOutputs + it.runtimeDependencyFiles!!
    }
    // Вывод UTF-8: без этого кириллица в консоли Windows превращается в вопросы, и
    // диагностический вывод, ради которого программа и написана, читать нельзя.
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
    // Переменные окружения пробрасываются как есть: TIMA_STAND_HOST,
    // TIMA_ESCROW_SIGNING_PUB, TIMA_PHONE_A, TIMA_PHONE_B.
    environment(System.getenv())
}
