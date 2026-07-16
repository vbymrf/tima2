package io.tima.app.platform

import io.tima.app.api.AppVersionDto
import java.awt.Desktop
import java.net.URI

// Синхронизировать с versionCode в composeApp/build.gradle.kts при выпуске версии.
private const val DESKTOP_VERSION_CODE = 1

actual fun currentVersionCode(): Int = DESKTOP_VERSION_CODE

actual suspend fun installUpdate(update: AppVersionDto) {
    // Десктоп распространяется как uber-jar/AppImage и ставится вручную —
    // открываем страницу загрузки, установку пользователь делает сам.
    if (update.url.isNotEmpty() && Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(URI(update.url))
    }
}
