package io.tima.app.platform

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import io.tima.app.api.AppVersionDto
import io.tima.app.diag.AppDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Application Context для скачивания/установки; ставит MainActivity. */
object AndroidAppContext {
    lateinit var app: Context

    /** Текущая Activity: нужна для флагов окна (не гасить экран во время звонка). */
    var activity: Activity? = null
}

actual fun currentVersionCode(): Int {
    val ctx = AndroidAppContext.app
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode.toInt()
    } else {
        @Suppress("DEPRECATION")
        info.versionCode
    }
}

actual fun currentVersionName(): String {
    val ctx = AndroidAppContext.app
    return runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
        .getOrNull().orEmpty()
}

actual suspend fun installUpdate(update: AppVersionDto, onProgress: (Int) -> Unit): Unit = withContext(Dispatchers.IO) {
    val ctx = AndroidAppContext.app
    try {
        // Android 8+: без разрешения «устанавливать из этого источника» установщик молча
        // закроется. Проверяем ДО скачивания и, если не выдано, ведём в настройки.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            AppDiagnostics.add("update: нет разрешения на установку — открываю настройки")
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            throw IllegalStateException("Разреши установку из этого источника и нажми «Обновить» снова")
        }
        // Системный DownloadManager: надёжно тянет большой APK и работает в фоне
        // (простой поток на 31 МБ подвисал).
        AppDiagnostics.add("update: ставлю ${update.versionName} (сборка ${update.versionCode}); сейчас установлена ${currentVersionName()} (${currentVersionCode()})")
        AppDiagnostics.add("update: качаю через DownloadManager ${update.url}")
        // Имя файла с номером сборки: при общем имени DownloadManager, увидев
        // существующий файл, пишет рядом «-1», а мы отдавали установщику старый.
        val name = "tima-update-${update.versionCode}.apk"
        val apk = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
        if (apk.exists()) apk.delete()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(update.url))
                .setTitle("TIMA ${update.versionName}")
                .setDescription("Обновление приложения")
                // ТОЛЬКО прогресс, без «нажми, чтобы открыть» после загрузки: по такому
                // нажатию APK открывает СИСТЕМА своим обработчиком (на Samsung им
                // оказывается посторонний компонент), и разрешение на установку она
                // просит для него, а не для нас. Установщик запускаем сами — ниже.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
                .setMimeType("application/vnd.android.package-archive"),
        )
        var waited = 0
        while (true) {
            delay(700)
            waited++
            val status = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return@use -1
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                // Прогресс в самом приложении: без него ждать нечего и рука тянется
                // к уведомлению — то есть к чужому пути установки
                if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) break
            if (status == DownloadManager.STATUS_FAILED) throw IllegalStateException("Загрузка не удалась")
            if (status == -1) throw IllegalStateException("Загрузка пропала из очереди")
            if (waited > 170) throw IllegalStateException("Загрузка слишком долгая — проверьте сеть") // ~2 мин
        }
        onProgress(100)
        AppDiagnostics.add("update: скачано ${apk.length()} байт в $name")

        // Смотрим, ЧТО именно скачали, до того как отдать установщику. Раньше файл
        // уходил в систему непроверенным, и подмена версии или битая загрузка
        // выглядели одинаково — «не ставится».
        val info = UpdateInstaller.inspect(ctx, apk)
        if (info == null) {
            throw IllegalStateException("Скачанный файл не читается как APK — попробуйте ещё раз")
        }
        AppDiagnostics.add("update: в файле ${info.packageName} ${info.versionName} (${info.versionCode}), подпись ${info.certSha256.take(12)}…")

        if (info.packageName != ctx.packageName) {
            throw IllegalStateException("Скачан чужой пакет (${info.packageName}) — установка отменена")
        }
        val installed = UpdateInstaller.installedCertSha256(ctx)
        AppDiagnostics.add("update: подпись установленного ${installed.take(12)}…")
        if (installed.isNotEmpty() && info.certSha256.isNotEmpty() && installed != info.certSha256) {
            // Android не даёт обновить приложение, подписанное другим ключом. Это не
            // сбой загрузки и не ошибка сервера: единственный выход — удалить и
            // поставить заново. Установщик в этом случае молча откажет, поэтому
            // говорим прямо и сразу ведём на экран удаления.
            AppDiagnostics.add("update: ПОДПИСЬ НЕ СОВПАЛА — обновление поверх невозможно")
            UpdateInstaller.openUninstall(ctx)
            throw IllegalStateException(
                "Эта сборка подписана другим ключом. Удалите TIMA и установите заново — " +
                    "дальше обновления будут ставиться как обычно.",
            )
        }
        UpdateInstaller.install(ctx, apk)
    } catch (e: Throwable) {
        AppDiagnostics.add("update: ошибка — ${e.message ?: e::class.simpleName}")
        throw e
    }
}
