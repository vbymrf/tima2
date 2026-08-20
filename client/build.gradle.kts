// Клиент TIMA v2 (ADR-0001): Kotlin Multiplatform + Compose Multiplatform.
//
// Постройка заново — doc_mig/Plan.md. Цели: Android, ПК (Windows) и iOS, причём
// iOS-таргет объявлен с первого коммита и держится компилируемым (Plan.md К1):
// именно это физически не даёт JVM-коду просочиться в общий и повторить историю
// с jvmCommon, где в v1 осело 1670 строк логики, отрезанной от iOS.

// Все плагины объявляются здесь с версией и `apply false`, даже если применяются
// в одном модуле. Иначе Gradle отказывает: «плагин уже на classpath с неизвестной
// версией, совместимость проверить нельзя» — так упал первый прогон, когда
// architecture-tests запросил kotlin.jvm с версией, а kotlin.multiplatform уже
// лежал на classpath из этого блока.
plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}
