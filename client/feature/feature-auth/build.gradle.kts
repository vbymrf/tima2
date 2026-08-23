plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// feature-auth — вход: телефон, код, заведение устройства (К5.1).
//
// Водопровод регистрации уже готов и проверен на живом стенде (К4.3, domain-account):
// здесь только экраны и правила поведения экрана. Сети и криптографии в модуле нет —
// это проверяется архитектурным правилом.
//
// ЧЕГО ЗДЕСЬ НЕТ И ПОЧЕМУ. doc_UI/22-auth-registration.md описывает ещё email, пароль,
// десять резервных кодов и временный режим. Ничего из этого на сервере не существует —
// измерено 2026-08-22, — и вопрос вынесен заказчику (ВОПРОСЫ-К-ЗАКАЗЧИКУ.md, Д1 и Д2).
// Сделано то, что сервер умеет: это подмножество любого из двух решений, и переделывать
// его не придётся.
kotlin {
    jvmToolchain(17)

    jvm()
    // ANDROID-ТАРГЕТ ОБЯЗАТЕЛЕН, И ЭТО НЕ ФОРМАЛЬНОСТЬ.
    //
    // Без него Android-приложение забирает **jvm-вариант** этого модуля — то есть код,
    // собранный против Compose для ПК. Собирается, ставится, запускается; падает на
    // первом же вызове, у которого реализация платформенная: `Path()` на ПК приводит к
    // skiko, и на телефоне это `NoClassDefFoundError: SkiaBackedPath_skikoKt`.
    //
    // Поймано живым прогоном: вход и список нарисовались, а первый пузырь переписки уронил
    // приложение. Compose Multiplatform совпадает по именам классов, поэтому подмена
    // варианта видна только там, где реализация действительно разная.
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.domain.domainAccount)
            implementation(projects.core.coreUi)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.kotlinx.coroutines.core)
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
    namespace = "io.tima.feature.auth"
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
