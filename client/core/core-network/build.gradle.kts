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
            // Порт регистрации объявлен в domain-account, реализация здесь: слой данных
            // реализует объявленное выше — то же направление, что у core-database с OutboxStore.
            api(projects.domain.domainAccount)
            implementation(libs.ktor.client.core)
            // Живой канал: один сокет на устройство (websocket-events.md).
            implementation(libs.ktor.client.websockets)
            // Тело ошибки сервера — {code, message}. Разбирается напрямую, без
            // ktor-content-negotiation: одна структура не стоит плагина.
            implementation(libs.kotlinx.serialization.json)
            // Время — kotlinx-datetime: сервер отдаёт RFC3339, а в подпись анклава входят
            // миллисекунды. java.time запрещён правилом — он падает на iOS.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Только в тестах: подписанный конфиг маршрутов проверяется настоящей
            // Ed25519, а не заглушкой. Боевой код криптографии не знает — проверка
            // приходит в SignatureCheck, чтобы сетевой слой не тянул крипто-библиотеку.
            implementation(projects.core.coreEncryption)
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
