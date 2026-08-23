plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// feature-chat — окно переписки (Plan.md §2.2): Store и экран.
//
// Экран — чистый рендер состояния: он ничего не считает и никуда не обращается, всё
// решение принято в Store. Это признак готовности К5, и проверяется он снимками.
//
// Зависимость одна и та же навсегда: слой представления говорит с Domain через случаи
// использования. Ktor, SQLDelight и крипто здесь запрещены архитектурным правилом.
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
            api(projects.domain.domainChat)
            // Дизайн-система: экран собирается из её деталей и своих цветов не имеет.
            implementation(projects.core.coreUi)
            implementation(compose.runtime)
            implementation(compose.foundation)
            // Время сообщения — kotlinx-datetime: java.time запрещён архитектурным
            // правилом, и не зря — на iOS его нет.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Снимки экрана: те же, что у дизайн-системы, и тем же способом.
        jvmTest.dependencies {
            implementation(projects.test.testUi)
        }
    }
}

android {
    namespace = "io.tima.feature.chat"
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
