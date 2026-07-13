package io.tima.app.session

import java.io.File

private lateinit var sessionFile: File

/** Вызывается из MainActivity до первого обращения к SessionStorage. */
fun initSessionDir(filesDir: File) {
    sessionFile = File(filesDir, "session.json")
}

actual object SessionStorage {
    actual fun read(): String? =
        sessionFile.takeIf { it.exists() }?.readText()

    actual fun write(text: String?) {
        if (text == null) sessionFile.delete() else sessionFile.writeText(text)
    }
}

// Эмулятор Android видит localhost хоста как 10.0.2.2
actual fun defaultServerUrl(): String = "http://10.0.2.2:8080"
