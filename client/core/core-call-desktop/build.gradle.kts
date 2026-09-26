import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.wire)
}

// core-call-desktop — звонок на ПК, маршрут A (doc_mig/ПЛАН-ЗВОНКОВ-ПК.md).
//
// Движок — `livekit-ffi`: клиентский Rust SDK LiveKit, собранный самой LiveKit в одну
// нативную библиотеку. Внутри неё libwebrtc целиком: кодеки, сеть, **микрофон и колонки
// через ADM Windows и эхоподавление APM** — ровно то, что на Android делает
// `livekit-android`. Наше здесь — обвязка: JNA к четырём C-функциям и протокол
// сообщений protobuf поверх них.
//
// Только JVM: у Android свой движок (`livekit-android` в core-call), у iOS — свой, когда
// будет машина.
kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            // api: движок отдаётся наружу типом контракта, и собирающий его (app-desktop)
            // обязан этот контракт видеть.
            api(projects.core.coreCall)
            implementation(projects.core.coreDiag)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.jna)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// ── Протокол FFI ──────────────────────────────────────────────────────────────
//
// Файлы `.proto` — копия `livekit-ffi/protocol` из `livekit/rust-sdks`, тег
// `livekit-ffi/v0.12.80` (коммит 5a656c4b), без правок. **Версия протокола обязана
// совпадать с версией библиотеки ниже**: сообщение новой версии старая библиотека
// разберёт по чужим номерам полей и ответит не тем. Обновляются только вместе.
wire {
    sourcePath {
        srcDir("src/proto")
    }
    kotlin {}
}

// ── Нативная библиотека: скачивается, а не лежит в git ────────────────────────
//
// 25 МБ двоичного файла в истории git остались бы там навсегда, при каждом обновлении
// ещё по 25. Поэтому сборка берёт библиотеку из релиза LiveKit по закреплённой версии и
// **сверяет sha256**: подменённый архив сборку роняет, а не попадает в установщик.
//
// Пока только Windows x86_64 — это все ПК заказчика. macOS и Linux — тот же релиз,
// другой архив, когда понадобятся.
val ffiVersion = "0.12.80"
val ffiArchive = "ffi-windows-x86_64.zip"
val ffiSha256 = "7118d627701e6eb9d15b4abbfbab91c8ef72ca87c94483e1774c863a57a7c552"

// Раскладка — та, что ждёт `appResourcesRootDir` у Compose Desktop: подкаталог по ОС.
// При запуске Compose кладёт его содержимое в `compose.application.resources.dir`.
val ffiRoot = layout.buildDirectory.dir("livekit-ffi")

val livekitFfi by tasks.registering {
    group = "build"
    description = "Скачивает livekit_ffi.dll из релиза LiveKit и сверяет sha256"
    val into = ffiRoot
    val version = ffiVersion
    val archive = ffiArchive
    val sha = ffiSha256
    inputs.property("version", version)
    inputs.property("sha256", sha)
    outputs.dir(into)
    doLast {
        val dll = into.get().file("windows/livekit_ffi.dll").asFile
        dll.parentFile.mkdirs()
        // Тег содержит «/», и в адресе он кодируется: `livekit-ffi%2Fv0.12.80`.
        val url = "https://github.com/livekit/rust-sdks/releases/download/livekit-ffi%2Fv$version/$archive"
        val bytes = URI(url).toURL().openStream().use { it.readBytes() }
        val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(got == sha) {
            "$archive: sha256 $got, ждали $sha. Архив подменён или версия сменилась без правки хэша"
        }
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.name.endsWith("livekit_ffi.dll")) dll.writeBytes(zip.readBytes())
            }
        }
        check(dll.length() > 0) { "в $archive нет livekit_ffi.dll" }
    }
}

// Проверки с настоящей библиотекой: путь к ней — свойством, как его даст и Compose.
tasks.named<Test>("jvmTest") {
    dependsOn(livekitFfi)
    systemProperty("compose.application.resources.dir", ffiRoot.get().dir("windows").asFile.absolutePath)
}
