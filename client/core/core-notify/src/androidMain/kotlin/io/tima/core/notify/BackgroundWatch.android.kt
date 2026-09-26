package io.tima.core.notify

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Три ответа системы — для журнала и снимка отчёта (ВЗ0в).
 *
 * `areNotificationsEnabled` покрывает оба запрета сразу: невыданное `POST_NOTIFICATIONS`
 * на Android 13+ и выключатель человека в настройках приложения. Канал «Звонки» спрашивается
 * отдельно: его можно выключить одного, при разрешённых остальных. Канала ещё нет (ни
 * одного входящего не было) — `null`: выключить его человек не мог.
 */
actual fun backgroundFacts(): BackgroundFacts {
    val context = AndroidNotices.contextOrNull() ?: return BackgroundFacts()
    val manager = context.getSystemService(NotificationManager::class.java)
    val notices = manager?.let { runCatching { it.areNotificationsEnabled() }.getOrNull() }
    val calls = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager?.getNotificationChannel(AndroidNotifier.CHANNEL_CALL)
            ?.let { it.importance != NotificationManager.IMPORTANCE_NONE }
    } else {
        null
    }
    val awake = context.getSystemService(PowerManager::class.java)
        ?.let { runCatching { it.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() }
    return BackgroundFacts(notices = notices, calls = calls, awake = awake)
}

actual fun openCallsChannelSettings() {
    val context = AndroidNotices.contextOrNull() ?: return
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, AndroidNotifier.CHANNEL_CALL)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
