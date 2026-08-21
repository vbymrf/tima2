// messenger-crypto: единственное место криптографии в проекте (Plan.md §3.5).
//
// ── ПОЧЕМУ KMP, а не отдельная реализация на каждую платформу ────────────────
// Криптография — единственный слой, где расхождение платформ не «баг», а
// нечитаемая переписка: два клиента, посчитавшие HKDF по-разному, не смогут
// расшифровать друг друга никогда. Поэтому один код на все платформы, а
// доказательство — тест-векторы из schema/test-vectors, сходящиеся байт в байт
// и на JVM, и на iOS.
//
// ── ЧЕГО ЗДЕСЬ НЕТ И ПОЧЕМУ ─────────────────────────────────────────────────
// `jvmCommon` не заводится. Общий код обязан компилироваться под iOS, иначе
// бизнес-логика отрезается от Apple первой же зависимостью (Plan.md §2.2,
// правило 3). Любой java.* в commonMain — ошибка компиляции, и это защита, а не
// неудобство.

plugins {
    kotlin("multiplatform") version "2.3.10"
    id("com.squareup.wire") version "5.2.1" // кодоген из ../schema/proto (ADR-0009: схема — источник)
    // Разметка сообщения (ADR-0011) — читаемый JSON внутри зашифрованного тела
    kotlin("plugin.serialization") version "2.3.10"
}

group = "io.tima"
version = "0.1.0"

kotlin {
    jvmToolchain(17)

    jvm()

    // Три таргета Apple, а не один: симулятор на Apple Silicon (arm64),
    // симулятор на Intel (x64) и устройство (arm64). Прогон идёт на
    // iosSimulatorArm64 — раннеры macOS в GitHub Actions на Apple Silicon.
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            // NaCl: SecretBox, Box, Ed25519, HKDF. api, а не implementation:
            // типы Kodium видны в подписях (KodiumPrivateKey).
            api("eu.livotov.labs:kodium:1.0.0")

            // ML-KEM-768 (FIPS 203) для escrow. Реализация ML-KEM внутри Kodium
            // не интероперабельна со стандартом (ADR-0005 Поправка-1), поэтому
            // берётся апстрим того же кода — KyberKotlin.
            //
            // ВАЖНО: артефакты Apple апстрим не публикует, поэтому здесь
            // разрешается наш форк из ../third-party с теми же координатами.
            // Он обязан лежать в mavenLocal, и mavenLocal идёт ПЕРЕД
            // mavenCentral в settings.gradle.kts — иначе подтянется апстрим без
            // таргетов Apple, и iOS не соберётся.
            implementation("asia.hombre:kyber:2.0.1")

            // Сжатие body ДО шифрования. Заменило zstd-jni (только JVM):
            // zstd-kmp даёт все таргеты, включая Apple, без cinterop.
            implementation("com.squareup.zstd:zstd-kmp:0.4.0")

            // api, а не implementation: Markup — часть публичного контракта
            // библиотеки, клиент разбирает и собирает разметку теми же типами.
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

            // SHA-256 вместо java.security.MessageDigest: чистый Kotlin, все
            // таргеты. Своей реализации хеша не пишем — «не изобретать
            // примитивы» дороже, чем одна зависимость.
            //
            // Отдельной библиотеки CSPRNG здесь НЕТ намеренно: единственное
            // место, где нужна случайность помимо самих Kodium и Kyber, — это
            // мнемоника, и она берёт её у Kodium. Второй источник случайности в
            // криптомодуле — это второй способ получить её плохо.
            implementation("org.kotlincrypto.hash:sha2:0.8.0")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // BouncyCastle — независимый оракул, и он ТОЛЬКО для JVM: в продукт не
        // едет и на iOS не существует. Сверка двух реализаций на каждой сборке
        // (CrossImplementationTest); отсутствие ровно такой сверки и позволило
        // дефекту ML-KEM в Kodium дожить до релиза (ADR-0005 Поправка-2).
        jvmTest.dependencies {
            implementation("org.bouncycastle:bcprov-jdk18on:1.80")
        }
    }
}

wire {
    sourcePath {
        srcDir(rootDir.resolve("../schema/proto").path)
    }
    kotlin {}
}

// ── Тест-векторы: сгенерированная константа вместо ресурса ───────────────────
//
// В KMP ресурсы из commonTest недоступны единообразно: на JVM работает
// getResourceAsStream, на Kotlin/Native его нет вовсе. Поэтому единый источник
// (../schema/test-vectors/vectors.json) вкладывается в СГЕНЕРИРОВАННЫЙ исходник
// и читается одинаково на всех таргетах.
//
// Копии вектора в репозитории при этом не появляется: файл только один,
// генерация идёт при каждой сборке. Правило «вектор не правится, чтобы тест
// позеленел» этим не нарушается — генератор читает, но не пишет.
val vectorsFile = rootDir.resolve("../schema/test-vectors/vectors.json")

val generateVectors by tasks.registering {
    val src = vectorsFile
    val outDir = layout.buildDirectory.dir("generated/vectors/kotlin")
    inputs.file(src)
    outputs.dir(outDir)
    doLast {
        val target = outDir.get().asFile.resolve("io/tima/crypto/GeneratedVectors.kt")
        target.parentFile.mkdirs()

        // Экранирование, а не Base64: декодировать Base64 в общем коде можно
        // только через ExperimentalEncodingApi, и тащить опт-ин в тесты ради
        // чтения файла незачем. Экранируются четыре вещи, каждая обязательна:
        // обратный слэш (иначе \" внутри JSON порвёт литерал), кавычка, перевод
        // строки и знак доллара — он в литерале Kotlin начинает шаблон.
        val escaped = src.readText()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", "\\n")
            .replace("\$", "\\\$")

        target.writeText(
            buildString {
                appendLine("// СГЕНЕРИРОВАНО задачей generateVectors. Не править руками.")
                appendLine("// Источник: schema/test-vectors/vectors.json — единственный.")
                appendLine("package io.tima.crypto")
                appendLine()
                append("internal const val VECTORS_JSON: String = \"")
                append(escaped)
                appendLine("\"")
            },
        )
    }
}

kotlin.sourceSets.commonTest {
    kotlin.srcDir(generateVectors)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
