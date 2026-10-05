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
        // Погасить — после того как подняли: команда пришла в очередь следом за запуском
        // (см. `AndroidCallNotice.off`).
        // «Завершить» из шторки (ПЛАН-(В)-ВИДЕО.md В11): трубку кладёт тот, кто ведёт звонок, —
        // сервер и комната; служба погаснет сама, когда звонок кончится.
        if (intent?.action == HANG_UP) {
            Journal.note(LogCode.CALL, "трубку положили из шторки")
            CallNoticeActions.hangUp?.invoke()
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // Со своим номером запуска: пришла следом команда «поднять» (новый звонок) — служба
            // не гаснет, а поднимается ею. Без номера `stopSelf` гасил и её, и обещанный
            // системе подъём оставался невыполненным — Android закрывал приложение
            // (БЕДЫ 2026-09-30-служба-звонка-после-отмены).
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(TITLE).orEmpty()
        val text = intent?.getStringExtra(TEXT).orEmpty()
        val hangUpLabel = intent?.getStringExtra(HANG_UP_LABEL).orEmpty()
        val connectedAt = intent?.getLongExtra(CONNECTED_AT, 0L) ?: 0L
        val camera = intent?.getBooleanExtra(CAMERA, false) ?: false
        runCatching { raise(NOTICE_ID, notice(title, text, hangUpLabel, connectedAt), camera) }
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
    private fun raise(id: Int, notification: Notification, camera: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(id, notification)
            return
        }
        // Камера — в тип, только когда человек выбрал показывать себя свёрнутым (заказчик
        // 2026-10-01, 1в) и разрешение на неё есть: без разрешения система откажет всей службе.
        val withCamera = camera &&
            checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (withCamera) {
            val done = runCatching {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            }
            if (done.isSuccess) {
                Journal.note(LogCode.CALL, "служба звонка держит камеру в фоне")
                return
            }
            Journal.trouble(
                LogCode.CALL, "служба звонка не взяла камеру — свёрнутым видео не покажем",
                "причина" to (done.exceptionOrNull()?.message ?: "неизвестно"),
            )
        }
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun notice(title: String, text: String, hangUpLabel: String, connectedAt: Long): Notification {
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
        builder
            .setContentTitle(title)
            .setContentText(text)
            // Значок берётся у приложения: своего у core-call нет и заводить его ради
            // одной строки значило бы завести в модуле ресурсы.
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(back)
            // Смахнуть нельзя: убранное уведомление означало бы, что звонок идёт, а следа
            // его в системе нет.
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)

        // ── «АКТИВНЫЙ ЗВОНОК» ЗЕЛЁНЫМ ПУЗЫРЁМ (ПЛАН-(В)-ВИДЕО.md В11) ───────────────
        //
        // Android 12 и новее — системный вид звонка: зелёный пузырь со временем в строке
        // состояния, пока человек в другом приложении, и «Завершить» в шторке. Вид пузыря
        // у каждой оболочки свой. Старше — такого вида нет: зелёный цвет (где оболочка
        // красит) и кнопка «Завершить» обычным действием.
        val hangUp = PendingIntent.getService(
            this,
            HANG_UP_REQUEST,
            Intent(this, CallService::class.java).setAction(HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = android.app.Person.Builder().setName(text.ifBlank { title }).setImportant(true).build()
            builder.setStyle(Notification.CallStyle.forOngoingCall(person, hangUp))
        } else {
            builder.setColor(CALL_GREEN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setColorized(true)
            builder.addAction(Notification.Action.Builder(null, hangUpLabel.ifBlank { title }, hangUp).build())
        }
        // Счётчик времени разговора — с ответа, а не с набора.
        if (connectedAt > 0L) builder.setWhen(connectedAt).setShowWhen(true).setUsesChronometer(true)
        Journal.note(
            LogCode.CALL, "уведомление звонка",
            "вид" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "системный звонок" else "зелёное",
            "счётчик" to (connectedAt > 0L),
        )
        return builder.build()
    }

    internal companion object {
        const val TITLE = "title"
        const val TEXT = "text"
        const val HANG_UP_LABEL = "hangUpLabel"
        const val CONNECTED_AT = "connectedAt"
        const val CAMERA = "camera"

        /** Команда «положить трубку» — из шторки. */
        const val HANG_UP = "io.tima.core.call.HANG_UP"
        private const val HANG_UP_REQUEST = 4204

        /** Зелёный звонка — тот, что у звонилок. */
        private const val CALL_GREEN = 0xFF1E8E3E.toInt()

        /** Команда «погасить». Латиницей: это имя действия, которое видит система. */
        const val STOP = "io.tima.core.call.STOP"

        /** Один звонок за раз — значит и уведомление одно, с постоянным номером. */
        private const val NOTICE_ID = 4203
        private const val CHANNEL = "call"
    }
}
