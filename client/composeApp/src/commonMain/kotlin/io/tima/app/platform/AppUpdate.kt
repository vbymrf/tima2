package io.tima.app.platform

import io.tima.app.api.AppVersionDto

/** versionCode установленного приложения (Android: из PackageManager; Desktop: константа сборки). */
expect fun currentVersionCode(): Int

/**
 * Скачать APK обновления и запустить установку.
 *  - Android: качаем во внутренний кэш и отдаём системному установщику (FileProvider).
 *  - Desktop: приложение ставится вручную — открываем ссылку на загрузку в браузере.
 * Бросает исключение при ошибке скачивания.
 */
expect suspend fun installUpdate(update: AppVersionDto)
