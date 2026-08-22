plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// test-harness — фейки и сценарии без устройства (Plan.md §2.2, К4.6).
//
// Модуль тестовый, но исходники в commonMain: его потребители — тесты других
// модулей, а тестовые наборы никуда не публикуются.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.core.coreOutbox)
            api(projects.core.coreDatabase)
            // Разбор кадров живого канала: сценарий приёма пишется на кадрах сервера.
            api(projects.core.coreNetwork)
            api(projects.core.coreEncryption)
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
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
    }
}

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
