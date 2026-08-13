package io.tima.app.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Мост к запуску сканера камеры; реализацию ставит MainActivity (ScanContract). */
object AndroidQrScanner {
    var scan: (suspend () -> String?)? = null
}

actual fun deviceLinkScanSupported(): Boolean = true

actual suspend fun scanDeviceLinkQr(): String? {
    val ctx = AndroidAppContext.app
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        AndroidPermissions.request?.invoke(listOf(Manifest.permission.CAMERA))
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return null // отказал в разрешении — не ошибка, а обычный отказ пользователя
        }
    }
    return AndroidQrScanner.scan?.invoke()
}
