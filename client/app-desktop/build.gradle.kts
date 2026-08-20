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
            // Аксессор compose.material3 объявлен deprecated в Compose 1.11
            // («Specify dependency directly»), и предупреждение мы терпим осознанно:
            // прямая координата org.jetbrains.compose.material3:material3 в линии
            // 1.11 существует ТОЛЬКО в alpha (1.11.0-alpha01…07), а последняя
            // стабильная — 1.9.0. То есть «починка» означала бы либо alpha в
            // зависимостях, либо расхождение версий с рантаймом Compose 1.11.1.
            // Аксессор этим и ценен: он всегда даёт версию плагина.
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
