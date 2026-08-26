plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// feature-shell — оболочка: перечень окон, переключатель, рейка, каркас окна.
//
// Отдельный модуль, а не часть core-ui, потому что это не компоненты, а экраны: у
// переключателя окон есть своё состояние (кто я, сколько непрочитанного) и свои
// переходы. И не часть shared: там композиция, а здесь представление, которое
// обязано собираться и проверяться без базы, сети и крипты.
//
// Зависимостей на другие feature нет и не будет — это правило проверяется
// архитектурным тестом. Оболочка знает, КАКИЕ окна бывают, и ничего не знает о
// том, что у них внутри: содержимое ей передают.
kotlin {
    jvmToolchain(17)

    jvm()
    // Android-таргет обязателен: без него приложение забирает jvm-вариант модуля и
    // падает на первой платформенной реализации Compose. Ловилось живым прогоном.
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.coreUi)
            implementation(compose.runtime)
            implementation(compose.foundation)
            // Корутины — ради UpdateStore: проверка обновлений ходит в сеть, а сеть
            // за оболочкой (порт AppVersionPort), и держать её приходится ей.
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
    namespace = "io.tima.feature.shell"
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
