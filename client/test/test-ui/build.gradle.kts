plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// test-ui — снимки композиций для проверок (У.3, К5).
//
// Только JVM: `ImageComposeScene` из Compose Desktop рисует композицию в картинку без
// устройства, без эмулятора и без дисплея. Компоненты и экраны общие, поэтому снятое
// здесь расхождение есть расхождение вообще.
//
// Исходники в `main`, а не в `test`, по той же причине, что у `test-harness`: его
// потребители — тесты ДРУГИХ модулей, а тестовые наборы между модулями не разделяются.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(projects.core.coreUi)
    api(compose.desktop.currentOs)
}
