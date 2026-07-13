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

// Эмулятор Android видит localhost хоста как 10.0.2.2
actual fun defaultServerUrl(): String = "http://10.0.2.2:8080"
