plugins {
    kotlin("jvm") version "2.3.10"
    id("com.squareup.wire") version "5.2.1" // кодоген из ../schema/proto (ADR-0009: схема — источник)
    // Разметка сообщения (ADR-0011) — читаемый JSON внутри зашифрованного тела
    kotlin("plugin.serialization") version "2.3.10"
}

group = "io.tima"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("eu.livotov.labs:kodium:1.0.0")
    // ML-KEM-768 (FIPS 203) для escrow: реализация Kodium не интероперабельна со
    // стандартом (см. Mlkem768.kt и ADR-0005 Поправку-1). Берём апстрим того же кода —
    // KyberKotlin, где дефекта нет: 163 КБ против 8,68 МБ и поддержка KMP.
    implementation("asia.hombre:kyber:2.0.1")
    // BouncyCastle — независимый оракул для тестов, в продукт не едет. Сверка двух
    // реализаций на каждой сборке (CrossImplementationTest); отсутствие ровно такой
    // сверки и позволило дефекту Kodium дожить до релиза. ADR-0005 Поправка-2.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.github.luben:zstd-jni:1.5.6-9") // сжатие body ДО шифрования
    // api, а не implementation: Markup — часть публичного контракта библиотеки,
    // клиент разбирает и собирает разметку теми же типами.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}

wire {
    sourcePath {
        srcDir(rootDir.resolve("../schema/proto").path)
    }
    kotlin {}
}

// Тест-векторы — единый источник в ../schema/test-vectors, без копий в модуле.
sourceSets {
    test {
        resources {
            srcDir(rootDir.resolve("../schema/test-vectors"))
            include("vectors.json")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
