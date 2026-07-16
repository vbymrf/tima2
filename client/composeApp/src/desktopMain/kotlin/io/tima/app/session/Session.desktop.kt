package io.tima.app.session

import java.io.File

private val appDir: File by lazy {
    File(System.getProperty("user.home"), ".tima").apply { mkdirs() }
}

actual object SessionStorage {
    actual fun read(name: String): String? =
        appDir.resolve(name).takeIf { it.exists() }?.readText()

    actual fun write(name: String, text: String?) {
        val file = appDir.resolve(name)
        if (text == null) file.delete() else file.writeText(text)
    }
}

// Боевой сервер по умолчанию (punycode для пацак.рф). Поле «Сервер» редактируемое —
// для локальной разработки вписать http://127.0.0.1:8080 вручную.
actual fun defaultServerUrl(): String = "https://api.xn--80aa4ar0b.xn--p1ai"
