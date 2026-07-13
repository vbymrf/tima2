// Клиент TIMA (ADR-0001): Kotlin Multiplatform + Compose Multiplatform.
// Цели MVP: Android (APK) и Desktop/Windows (проверка без эмулятора). iOS — при появлении Mac.
plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("multiplatform") version "2.3.10" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
}
