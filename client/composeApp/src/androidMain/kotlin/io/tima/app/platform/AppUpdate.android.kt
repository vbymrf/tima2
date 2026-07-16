package io.tima.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.tima.app.api.AppVersionDto
import io.tima.app.diag.AppDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/** Application Context для скачивания/установки; ставит MainActivity. */
object AndroidAppContext {
    lateinit var app: Context
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

actual suspend fun installUpdate(update: AppVersionDto): Unit = withContext(Dispatchers.IO) {
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
        AppDiagnostics.add("update: скачиваю ${update.url}")
        val apk = File(ctx.cacheDir, "tima-update.apk")
        URL(update.url).openStream().use { input ->
            apk.outputStream().use { output -> input.copyTo(output) }
        }
        AppDiagnostics.add("update: скачано ${apk.length()} байт, запускаю установщик")
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    } catch (e: Throwable) {
        AppDiagnostics.add("update: ошибка — ${e.message ?: e::class.simpleName}")
        throw e
    }
}
