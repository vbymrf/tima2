package io.tima.core.call

/** Apple: приложения нет, и стенда тоже. */
actual fun saveBenchReport(fileName: String, text: String): String? = null

actual fun phoneModel(): String = "ios"

actual fun readPresetsFile(): String? = null

actual fun writePresetsFile(text: String): String? = null
