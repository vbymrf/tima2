plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// feature-call — окно звонка (ПЛАН-СТЕНДА-ЗВОНКОВ С2, макет doc/doc_UI/21-call.md).
//
// Отдельный модуль, а не раздел feature-chat: звонок — не переписка. У него своё окно,
// свой жизненный цикл и своя зависимость (core-call с медиа внутри), и тянуть её в экран
// переписки значило бы тянуть двадцать мегабайт libwebrtc туда, где они не нужны.
//
// Экран **чистый**: он рисует CallState и зовёт обратные вызовы. Ни LiveKit, ни сети он не
// видит — этим занят core-call, и смена исполнения его не касается.
kotlin {
    jvmToolchain(17)

    jvm()
    // Android-таргет обязателен: без него приложение забирает jvm-вариант модуля — код
    // против Compose для ПК. Ловилось живым прогоном у feature-chat, а не сборкой.
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.core.coreCall)
            implementation(projects.core.coreWords)
            implementation(projects.core.coreUi)
            implementation(compose.runtime)
            implementation(compose.foundation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(projects.test.testUi)
        }
    }
}

android {
    namespace = "io.tima.feature.call"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}

tasks.matching { it.name.startsWith("lint") }.configureEach { enabled = false }
tasks.matching { it.name.endsWith("UnitTest") }.configureEach { enabled = false }
