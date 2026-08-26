plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Вход для ПК. Здесь и только здесь разрешено знать о платформе как о платформе
// (Plan.md §1.3): окно, трей, автозапуск, fileовые диалоги.
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

// Версия для десктопа: BuildConfig есть только у Android, поэтому крошечный file
// порождается сборкой. Без него десктоп показывал «Установлена —», то есть не мог
// ответить на вопрос, который задают, когда что-то пошло не так.
val versionDir = layout.buildDirectory.dir("generated/tima-version")
// Свойства читаются ЗДЕСЬ, а не внутри задачи: внутри `registering` получатель — сама
// задача, и `property()` ищет у неё, а не у проекта. Ловится только при сборке.
val timaCode = property("tima.versionCode") as String
val timaName = property("tima.versionName") as String
val timaStream = property("tima.stream") as String
val timaVersion by tasks.registering {
    val into = versionDir
    val code = timaCode
    val name = timaName
    val stream = timaStream
    inputs.property("code", code)
    inputs.property("name", name)
    inputs.property("stream", stream)
    outputs.dir(into)
    doLast {
        val file = into.get().file("io/tima/app/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.tima.app

            /** Порождается сборкой из gradle.properties. Правится там, а не здесь. */
            internal const val BUILD_NAME: String = "$name"
            internal const val BUILD_CODE: Int = $code
            internal const val BUILD_STREAM: String = "$stream"
            """.trimIndent() + System.lineSeparator(),
        )
    }
}

kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(timaVersion) }

compose.desktop {
    application {
        mainClass = "io.tima.app.MainKt"
    }
}
