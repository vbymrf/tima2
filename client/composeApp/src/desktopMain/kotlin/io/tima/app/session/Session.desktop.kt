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

actual fun defaultServerUrl(): String = "http://127.0.0.1:8080"
