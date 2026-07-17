package io.tima.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.tima.app.MainActivity
import io.tima.app.diag.AppDiagnostics

/**
 * Foreground-сервис на время звонка. Android с 9-й версии отбирает микрофон и камеру
 * у ФОНОВОГО приложения: гаснет экран или свернули окно — звук пропадает. Пока висит
 * этот сервис, система считает звонок активной работой и доступ не отзывает.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Звонки", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Идёт звонок TIMA"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("TIMA")
            .setContentText(if (video) "Идёт видеозвонок" else "Идёт звонок")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        // Типы обязаны совпадать с android:foregroundServiceType в манифесте и с тем,
        // что реально используем: без microphone система глушит звук в фоне.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(ID, notification, type)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "tima_calls"
        private const val ID = 42
        private const val EXTRA_VIDEO = "video"

        fun start(ctx: Context, video: Boolean) {
            val intent = Intent(ctx, CallService::class.java).putExtra(EXTRA_VIDEO, video)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            }.onFailure { AppDiagnostics.add("звонок: сервис не запустился — ${it.message}") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, CallService::class.java)) }
        }
    }
}
