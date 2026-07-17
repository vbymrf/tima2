package io.tima.app.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Мост к запросу разрешений; реализацию ставит MainActivity (Activity Result API). */
object AndroidPermissions {
    var request: (suspend (List<String>) -> Boolean)? = null
}

actual suspend fun ensureCallPermissions(video: Boolean): Boolean {
    val ctx = AndroidAppContext.app
    val wanted = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (video) add(Manifest.permission.CAMERA)
        // Уведомление foreground-сервиса звонка (Android 13+); отказ звонок не ломает
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val need = wanted.filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
    if (need.isEmpty()) return true
    AndroidPermissions.request?.invoke(need)
    // Решают только микрофон и камера: без уведомления звонок работает
    val must = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (video) add(Manifest.permission.CAMERA)
    }
    return must.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
}
