package io.tima.app.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import io.tima.app.api.AppVersionDto
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
    // Качаем APK во внутренний кэш (перезаписываем прошлую попытку)
    val apk = File(ctx.cacheDir, "tima-update.apk")
    URL(update.url).openStream().use { input ->
        apk.outputStream().use { output -> input.copyTo(output) }
    }
    // Отдаём системному установщику через FileProvider (content:// URI, без file://)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(intent)
}
