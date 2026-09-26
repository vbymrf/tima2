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
            implementation(libs.jna.platform)
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

// ── Нативные библиотеки: скачиваются, а не лежат в git ────────────────────────
//
// 25 МБ двоичного файла в истории git остались бы там навсегда, при каждом обновлении
// ещё по 25. Поэтому сборка берёт библиотеки из релизов по закреплённой версии и
// **сверяет sha256**: подменённый файл сборку роняет, а не попадает в установщик.
//
// Пока только Windows x86_64 — это все ПК заказчика. macOS и Linux — те же релизы,
// другие файлы, когда понадобятся. Обе библиотеки зовут только системные DLL Windows
// (C-рантайм вшит), доставлять рядом ничего не надо — проверено по таблице импорта.
class NativeLib(val url: String, val sha256: String, val zipEntry: String?, val saveAs: String)

val nativeLibs = listOf(
    // Движок звонка: libwebrtc целиком, звук, APM. Тег содержит «/» — в адресе `%2F`.
    NativeLib(
        url = "https://github.com/livekit/rust-sdks/releases/download/livekit-ffi%2Fv0.12.80/ffi-windows-x86_64.zip",
        sha256 = "7118d627701e6eb9d15b4abbfbab91c8ef72ca87c94483e1774c863a57a7c552",
        zipEntry = "livekit_ffi.dll",
        saveAs = "livekit_ffi.dll",
    ),
    // Камера (ПК4): openpnp-capture v0.0.30, MIT, 200 КБ. Своего захвата камеры у
    // livekit-ffi нет — `capture.proto` умеет только тестовые картинки.
    NativeLib(
        url = "https://github.com/openpnp/openpnp-capture/releases/download/v0.0.30/libopenpnp-capture-windows-latest-x86_64.dll",
        sha256 = "3280266977f6ee70687d997455cd7463507ed5077d5de1235c4ef5d8f8e77b21",
        zipEntry = null,
        saveAs = "openpnp-capture.dll",
    ),
)

// Раскладка — та, что ждёт `appResourcesRootDir` у Compose Desktop: подкаталог по ОС.
// При запуске Compose кладёт его содержимое в `compose.application.resources.dir`.
val ffiRoot = layout.buildDirectory.dir("livekit-ffi")

val callNatives by tasks.registering {
    group = "build"
    description = "Скачивает нативные библиотеки звонка ПК и сверяет sha256"
    val into = ffiRoot
    val libs = nativeLibs
    inputs.property("libs", libs.joinToString { it.url + "#" + it.sha256 })
    outputs.dir(into)
    doLast {
        val dir = into.get().dir("windows").asFile
        dir.mkdirs()
        for (lib in libs) {
            val bytes = URI(lib.url).toURL().openStream().use { it.readBytes() }
            val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(got == lib.sha256) {
                "${lib.url}: sha256 $got, ждали ${lib.sha256}. Файл подменён или версия сменилась без правки хэша"
            }
            val target = File(dir, lib.saveAs)
            if (lib.zipEntry == null) {
                target.writeBytes(bytes)
            } else {
                ZipInputStream(bytes.inputStream()).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        if (entry.name.endsWith(lib.zipEntry)) target.writeBytes(zip.readBytes())
                    }
                }
            }
            check(target.length() > 0) { "в ${lib.url} нет ${lib.zipEntry ?: lib.saveAs}" }
        }
    }
}

// Проверки с настоящей библиотекой: путь к ней — свойством, как его даст и Compose.
tasks.named<Test>("jvmTest") {
    dependsOn(callNatives)
    systemProperty("compose.application.resources.dir", ffiRoot.get().dir("windows").asFile.absolutePath)
}
