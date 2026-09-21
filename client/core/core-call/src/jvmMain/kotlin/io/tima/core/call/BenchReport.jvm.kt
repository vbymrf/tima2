package io.tima.core.call

/** ПК: стенда нет, и складывать нечего (см. `PhoneMeter.jvm.kt`). */
actual fun saveBenchReport(fileName: String, text: String): String? = null

actual fun phoneModel(): String = "ПК"
