package io.tima.core.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode

/**
 * Служба переднего плана на время звонка.
 *
 * ── ЧТО ОНА НА САМОМ ДЕЛЕ ДЕЛАЕТ ────────────────────────────────────────────
 *
 * Уведомление в шторке — не её цель, а её цена. **Цель — не дать системе отобрать
 * микрофон**, когда приложение свернули: свёрнутое приложение без службы переднего плана
 * теряет запись, и так записано у Android.
 *
 * Обмерено 2026-09-20 на двух телефонах с одинаковым Android 11 после сворачивания:
 * Samsung держал сеанс микрофона час, realme оборвал его через три с половиной секунды.
 * Разница не в нашем коде — одни вендоры правило применяют, другие нет. Работавший
 * Samsung был случайностью.
 *
 * ── ПОЧЕМУ ТОЛЬКО МИКРОФОН, А НЕ КАМЕРА ─────────────────────────────────────
 *
 * Тип `camera` здесь **не объявлен намеренно**. С Android 14 объявленный тип требует
 * выданного разрешения: заявить камеру и не иметь её — `SecurityException` при запуске
 * службы, то есть падение вместо звонка, и ровно у тех, кто камеру не разрешил.
 *
 * Держать камеру в фоне и незачем: человек, ушедший в другое приложение, показывает
 * собеседнику потолок. Система остановит съёмку сама, звук при этом продолжится — это и
 * есть нужное поведение.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(TITLE).orEmpty()
        val text = intent?.getStringExtra(TEXT).orEmpty()
        runCatching { raise(NOTICE_ID, notice(title, text)) }
            .onFailure {
                // Служба не поднялась — значит микрофон в фоне мы не удержим. Сам звонок
                // при этом идёт, и обрывать его незачем; но в журнале это обязано
                // остаться: «на этом телефоне звук пропадает при сворачивании» иначе
                // ищется как беда сети.
                Journal.trouble(
                    LogCode.CALL,
                    "служба звонка не поднялась — микрофон в фоне не удержим",
                    "причина" to (it.message ?: it::class.simpleName ?: "неизвестно"),
                )
                stopSelf()
            }
        // NOT_STICKY: воскрешать службу после убийства процесса нечего — звонка,
        // ради которого она жила, к тому моменту уже нет.
        return START_NOT_STICKY
    }

    /**
     * Подняться с объявлением типа — там, где система его спрашивает (Android 10 и новее).
     *
     * Имя своё, а не `startForeground`: одноимённый метод есть у [Service], и перекрытие
     * с другой подписью читается как переопределение, которым не является.
     */
    private fun raise(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun notice(title: String, text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, title.ifBlank { "TIMA" }, NotificationManager.IMPORTANCE_LOW).apply {
                    // Тихо и без вибрации: звонок человек и так слышит, а уведомление
                    // здесь — след в шторке, а не сигнал.
                    setShowBadge(false)
                },
            )
        }

        // Нажатие возвращает в приложение. Класс главного окна отсюда не виден и видеть
        // его незачем — система знает, чем открывается наш пакет.
        val back = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            // Значок берётся у приложения: своего у core-call нет и заводить его ради
            // одной строки значило бы завести в модуле ресурсы.
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(back)
            // Смахнуть нельзя: убранное уведомление означало бы, что звонок идёт, а следа
            // его в системе нет.
            .setOngoing(true)
            .build()
    }

    internal companion object {
        const val TITLE = "title"
        const val TEXT = "text"

        /** Один звонок за раз — значит и уведомление одно, с постоянным номером. */
        private const val NOTICE_ID = 4203
        private const val CHANNEL = "call"
    }
}
