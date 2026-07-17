package io.tima.app.platform

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

actual fun startBackgroundService() = TimaService.start(AndroidAppContext.app)

actual fun stopBackgroundService() = TimaService.stop(AndroidAppContext.app)

actual fun backgroundSupported(): Boolean = true

actual fun backgroundServiceRunning(): Boolean = TimaService.running

actual fun batteryOptimizationIgnored(): Boolean {
    val ctx = AndroidAppContext.app
    val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

actual fun requestIgnoreBatteryOptimization() {
    val ctx = AndroidAppContext.app
    // Прямой intent требует особого разрешения и режется вендорами — ведём в общий
    // список, там пользователь снимает ограничение сам
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.recoverCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
