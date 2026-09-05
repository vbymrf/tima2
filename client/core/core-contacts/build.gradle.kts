plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// core-contacts — чтение телефонной книги устройства (ПЛАН-КОНТАКТОВ.md, Д3).
//
// Отдельный модуль по той же причине, что и core-secrets: поведение здесь ОБЯЗАНО
// отличаться по платформам, и общего кода почти нет. Android читает ContactsContract,
// Apple — CNContactStore, на ПК телефонной книги не существует вовсе, и там честный
// пустой ответ, а не заглушка, притворяющаяся чтением.
//
// Своей книги приложения здесь нет: этот модуль только читает чужое. Что с прочитанным
// делать — дело core-database и domain-chat.
kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Строка книги объявлена в домене: модуль отдаёт то, что домен умеет принять,
            // а не свой тип, который потом пришлось бы перекладывать.
            api(projects.domain.domainChat)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "io.tima.core.contacts"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Линт AGP выключен по той же причине, что и в остальных модулях: у него свой
    // встроенный Kotlin, он отстаёт от проектного и падает на метаданных, а не на коде.
    lint { abortOnError = false }
}

tasks.matching { it.name.startsWith("lint") }.configureEach { enabled = false }
