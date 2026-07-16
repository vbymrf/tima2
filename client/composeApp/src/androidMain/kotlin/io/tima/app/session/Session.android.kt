package io.tima.app.session

import java.io.File

private lateinit var appFilesDir: File

/** Вызывается из MainActivity до первого обращения к SessionStorage. */
fun initSessionDir(filesDir: File) {
    appFilesDir = filesDir
}

actual object SessionStorage {
    actual fun read(name: String): String? =
        File(appFilesDir, name).takeIf { it.exists() }?.readText()

    actual fun write(name: String, text: String?) {
        val file = File(appFilesDir, name)
        if (text == null) file.delete() else file.writeText(text)
    }
}

// Боевой сервер по умолчанию (punycode для пацак.рф). Поле «Сервер» редактируемое —
// для локальной разработки вписать http://10.0.2.2:8080 (эмулятор) вручную.
actual fun defaultServerUrl(): String = "https://api.xn--80aa4ar0b.xn--p1ai"
