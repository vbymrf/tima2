plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// core-ui — дизайн-система из макета: токены, тема, компоненты (Plan.md §2.2, дорожка У).
//
// Единственная крупная работа, которую можно вести без готового ядра, — и наоборот:
// экраны без неё собирать нечем. Источник значений — doc/Layout-UI-light/стиль.css и
// слой переопределений doc/Layout-UI-dark/тьма.css; один набор токенов, две темы.
//
// Зависимостей на данные здесь нет и не будет: дизайн-система ничего не знает ни о
// сети, ни о базе, ни о крипто. Это проверяют архитектурные правила.
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
            implementation(compose.runtime)
            // foundation, а не material3: у нас своя система форм и цветов, и брать
            // чужую тему значило бы спорить с макетом в каждом компоненте.
            implementation(compose.foundation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Снимки (У.3) рисуются только на JVM: Compose Desktop умеет отрисовать
        // композицию в картинку без устройства и без эмулятора — ImageComposeScene.
        // Компоненты общие, так что расхождение с макетом видно и здесь.
        jvmTest.dependencies {
            implementation(projects.test.testUi)
        }
    }
}

android {
    namespace = "io.tima.core.ui"
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
