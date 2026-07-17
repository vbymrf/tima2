package io.tima.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import io.tima.app.MainActivity
import io.tima.app.chat.ChatClientHolder
import io.tima.app.chat.preview
import io.tima.app.diag.AppDiagnostics
import io.tima.app.session.SessionCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Держит WS-соединение живым, пока приложение свёрнуто или закрыто, и показывает
 * уведомления о входящих. Это наша замена пушам через Google: сигнал о сообщении
 * не уходит наружу вообще — ни текст, ни сам факт.
 *
 * Текст уведомления берётся из УЖЕ расшифрованного на этом устройстве сообщения:
 * сервер открытого текста по-прежнему не видит.
 */
class TimaService : Service() {

    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        startForeground(ONGOING_ID, ongoingNotification())
        if (scope != null) return START_STICKY // уже работаем — повторный старт не плодит сборщиков

        val session = SessionCodec.load()
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        val client = ChatClientHolder.get(session)
        s.launch { client.start() }
        s.launch {
            client.messages.collect { msg ->
                // Своё эхо и открытый экран не тревожим — там всё и так видно
                if (msg.mine || AppForeground.visible) return@collect
                notify(MSG_ID, "Новое сообщение", msg.preview(), CH_MESSAGES)
            }
        }
        s.launch {
            client.incomingCalls.collect { call ->
                if (AppForeground.visible) return@collect
                val who = runCatching { client.resolvePeers(listOf(call.fromUserId)) }
                    .getOrNull()?.get(call.fromUserId)?.label ?: "Неизвестный номер"
                AppDiagnostics.add("входящий звонок в фоне: $who")
                notifyCall(who, call.kind == "video")
            }
        }
        AppDiagnostics.add("сервис: держу соединение в фоне")
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ongoingNotification(): Notification =
        Notification.Builder(this, CH_ONGOING)
            .setContentTitle("TIMA")
            .setContentText("Готов принимать сообщения и звонки")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    private fun notify(id: Int, title: String, text: String, channel: String) {
        val n = Notification.Builder(this, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { getSystemService(NotificationManager::class.java)?.notify(id, n) }
    }

    private fun notifyCall(who: String, video: Boolean) {
        val n = Notification.Builder(this, CH_CALLS)
            .setContentTitle(if (video) "Видеозвонок" else "Входящий звонок")
            .setContentText(who)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            // Звонок должен разбудить экран, а не тихо лечь в шторку
            .setFullScreenIntent(openApp(), true)
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        runCatching { getSystemService(NotificationManager::class.java)?.notify(CALL_ID, n) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Работа в фоне", NotificationManager.IMPORTANCE_MIN).apply {
                description = "TIMA на связи: без этого не придут звонки и сообщения"
                setShowBadge(false)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH),
        )
        mgr.createNotificationChannel(
            NotificationChannel(CH_CALLS, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH).apply {
                setBypassDnd(true)
            },
        )
    }

    companion object {
        private const val CH_ONGOING = "tima_ongoing"
        private const val CH_MESSAGES = "tima_messages"
        private const val CH_CALLS = "tima_incoming_calls"
        private const val ONGOING_ID = 7
        private const val MSG_ID = 8
        private const val CALL_ID = 9

        fun start(ctx: Context) {
            val intent = Intent(ctx, TimaService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            }.onFailure { AppDiagnostics.add("сервис: не запустился — ${it.message}") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, TimaService::class.java)) }
        }
    }
}

/** Виден ли экран приложения: в фореграунде уведомления не нужны — всё видно и так. */
object AppForeground {
    @Volatile var visible: Boolean = false
}
