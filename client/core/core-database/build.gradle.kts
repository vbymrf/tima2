plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.sqldelight)
}

// core-database — локальное хранилище. Схема Plan.md §3.4.2, драйверы по платформам.
//
// Здесь же живёт реализация OutboxStore: по плану очередь исходящих это СТОЛБЦЫ
// таблицы messages, а не отдельная таблица, — иначе у одного сообщения два
// источника правды. Интерфейс объявлен в core-outbox, реализация здесь: слой Data
// реализует то, что объявлено выше.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.core.coreOutbox)
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
        // Драйвер Kotlin/Native — один на все таргеты Apple.
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
        iosTest.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
    }
}

sqldelight {
    databases {
        create("TimaDatabase") {
            packageName.set("io.tima.core.database")
            // Схема версионируется файлами миграций, а не «как получится»:
            // локальная база переживает обновление приложения, и потерянная миграция
            // означает потерянную переписку.
            //
            // Снимок схемы лежит в репозитории и обновляется задачей
            // generateCommonMainTimaDatabaseSchema. Без него verifyMigrations падает с
            // «requires a database file to be present» — то есть проверка не включается
            // молча, и это правильно: сравнивать миграции не с чем.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
