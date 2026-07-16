package io.tima.app.platform

import android.Manifest
import android.content.pm.PackageManager
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
    }
    val need = wanted.filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
    if (need.isEmpty()) return true
    return AndroidPermissions.request?.invoke(need) ?: false
}
