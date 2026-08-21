plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// core-network. Состояние связи перенесено из v1 (единственная часть сетевого слоя,
// измеренная в живой мобильной сети), маршрут собирается из конфигурации, транспорт
// — на Ktor с движком по платформе.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // SendOutcome — словарь очереди, и транспорт говорит на нём: заводить
            // второй набор тех же четырёх исходов ради «чистоты слоёв» значило бы
            // писать преобразование, которое однажды разойдётся с оригиналом.
            api(projects.core.coreOutbox)
            implementation(libs.ktor.client.core)
            // Тело ошибки сервера — {code, message}. Разбирается напрямую, без
            // ktor-content-negotiation: одна структура не стоит плагина.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
