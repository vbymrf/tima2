plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Вход для ПК. Здесь и только здесь разрешено знать о платформе как о платформе
// (Plan.md §1.3): окно, трей, автозапуск, файловые диалоги.
//
// Здесь же — единственное место, где всё соединяется: секрет устройства из хранилища
// платформы, база с диска, очередь, экраны. Ни один модуль ниже не знает, кто его собрал.
kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            // material3 больше не нужен и убран: у нас своя система форм и цветов, и
            // единственным его потребителем был пустой каркас К1.9. Заодно ушло
            // предупреждение об устаревшем аксессоре, которое приходилось терпеть.
            implementation(project(":core:core-model"))
            // Дизайн-система, экраны и весь стек хранения. Приложение только соединяет.
            implementation(project(":core:core-ui"))
            implementation(project(":core:core-database"))
            implementation(project(":core:core-encryption"))
            implementation(project(":core:core-secrets"))
            implementation(project(":feature:feature-chat"))
            implementation(project(":feature:feature-auth"))
            // Сеть: регистрация устройства идёт по HTTP. Движок выбирает core-network,
            // здесь только его подключение.
            implementation(project(":core:core-network"))
            implementation(libs.ktor.client.core)
            // Живой канал событий: тот же клиент, что и для REST.
            implementation(libs.ktor.client.websockets)
        }
        // Сборку приложения можно проверить без окна: секрет, база и очередь — обычный
        // код. Именно здесь ломается первый живой запуск, а не в отрисовке.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.tima.app.MainKt"
    }
}
