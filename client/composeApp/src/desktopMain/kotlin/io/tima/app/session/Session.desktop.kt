package io.tima.app.session

import java.io.File

private val sessionFile: File by lazy {
    File(System.getProperty("user.home"), ".tima").apply { mkdirs() }.resolve("session.json")
}

actual object SessionStorage {
    actual fun read(): String? =
        sessionFile.takeIf { it.exists() }?.readText()

    actual fun write(text: String?) {
        if (text == null) sessionFile.delete() else sessionFile.writeText(text)
    }
}

actual fun defaultServerUrl(): String = "http://127.0.0.1:8080"
