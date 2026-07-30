package io.tima.app.platform

import io.tima.app.api.AppVersionDto
import java.awt.Desktop
import java.net.URI

// Синхронизировать с versionCode в composeApp/build.gradle.kts при выпуске версии.
private const val DESKTOP_VERSION_CODE = 13

// Синхронизировать с versionName в composeApp/build.gradle.kts при выпуске версии.
private const val DESKTOP_VERSION_NAME = "0.5.6"

actual fun currentVersionCode(): Int = DESKTOP_VERSION_CODE

actual fun currentVersionName(): String = DESKTOP_VERSION_NAME

actual suspend fun installUpdate(update: AppVersionDto, onProgress: (Int) -> Unit) {
    // Десктоп распространяется как uber-jar/AppImage и ставится вручную —
    // открываем страницу загрузки, установку пользователь делает сам.
    if (update.url.isNotEmpty() && Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(URI(update.url))
    }
}
