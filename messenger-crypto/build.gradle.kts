plugins {
    kotlin("jvm") version "2.3.10"
    id("com.squareup.wire") version "5.2.1" // кодоген из ../schema/proto (ADR-0009: схема — источник)
}

group = "io.tima"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("eu.livotov.labs:kodium:1.0.0")
    // ML-KEM-768 (FIPS 203) для escrow: Kodium-реализация не интероперабельна со стандартом
    // (см. Mlkem768.kt и поправку к ADR-0005) — BouncyCastle до исправления upstream.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.github.luben:zstd-jni:1.5.6-9") // сжатие body ДО шифрования

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
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
