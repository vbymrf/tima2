plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
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
    // Android-таргет нужен не для полноты: драйвер здесь ОБЯЗАН быть своим.
    // jvm-вариант принёс бы sqlite-jdbc, то есть чужой SQLite рядом с системным.
    androidTarget()
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
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
        }
        androidUnitTest.dependencies {
            // Хостовые проверки: системного SQLite Android на машине сборки нет.
            implementation(libs.sqldelight.driver.jvm)
        }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
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

android {
    namespace = "io.tima.core.database"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // Настройки соединения проверяются НА УСТРОЙСТВЕ: на JVM ровно эта проверка и
        // поймала ложное обещание secure_delete.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Линт AGP выключен по той же причине, что и в app-android: у него свой встроенный
    // Kotlin, он отстаёт от проектного и падает на метаданных, а не на коде.
    lint { abortOnError = false }
}

tasks.matching { it.name.startsWith("lint") }.configureEach { enabled = false }

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
