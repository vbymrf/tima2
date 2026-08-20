plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Вход для ПК. Здесь и только здесь разрешено знать о платформе как о платформе
// (Plan.md §1.3): окно, трей, автозапуск, файловые диалоги.
//
// Приложение намеренно пустое: задача К1.9 — доказать, что тулчейн Compose
// собирается и запускается, а не показать интерфейс. Интерфейс приезжает в К5,
// по макету, из готовой дизайн-системы.
kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            // Без type-safe accessors: их нужно включать feature preview в settings,
            // а лишний переключатель ради красоты записи — цена без выгоды.
            implementation(project(":core:core-model"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.tima.app.MainKt"
    }
}
