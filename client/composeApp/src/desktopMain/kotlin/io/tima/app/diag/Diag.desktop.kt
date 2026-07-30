package io.tima.app.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun diagNow(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

actual fun deviceModel(): String {
    val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        ?: System.getenv("COMPUTERNAME") ?: "ПК"
    return "$host, ${System.getProperty("os.name")} ${System.getProperty("os.version")}"
}
