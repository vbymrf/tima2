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
            // Всё общее — одним модулем. Платформенного здесь два: драйвер базы и
            // окно.
            implementation(project(":shared"))
            implementation(project(":core:core-database"))
            implementation(project(":core:core-ui"))
        }

    }
}

compose.desktop {
    application {
        mainClass = "io.tima.app.MainKt"
    }
}
