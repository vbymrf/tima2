plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// feature-group — группы (К6.1): создание, состав, Store и экраны.
//
// Отдельный модуль, а не раздел feature-chat: у группы своя работа — состав и ключ, —
// и класть её к окну переписки значило бы, что экран переписки начнёт зависеть от
// управления участниками, которое ему не нужно.
//
// Зависимость одна и та же навсегда: слой представления говорит с Domain через случаи
// использования. Ktor, SQLDelight и крипто здесь запрещены архитектурным правилом.
kotlin {
    jvmToolchain(17)

    jvm()
    // Android-таргет обязателен: без него приложение забирает jvm-вариант модуля, то есть
    // код против Compose для ПК, и падает на первой же платформенной реализации. Это
    // ловилось живым прогоном у feature-chat, а не сборкой.
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.domain.domainChat)
            implementation(projects.core.coreUi)
            implementation(compose.runtime)
            implementation(compose.foundation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(projects.test.testUi)
        }
    }
}

android {
    namespace = "io.tima.feature.group"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}

tasks.matching { it.name.startsWith("lint") }.configureEach { enabled = false }

// Хостовые unit-тесты Android не нужны: они наследуют commonTest и прогнали бы то же
// самое, что уже идёт на таргете jvm. Платформенного у этого модуля нет вовсе.
tasks.matching { it.name.endsWith("UnitTest") }.configureEach { enabled = false }
