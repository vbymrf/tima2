package io.tima.app

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
import io.tima.core.database.androidDatabase
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.notify.BackgroundWatch
import io.tima.shared.ChannelHost
import io.tima.shared.Entry
import io.tima.shared.Platform
import io.tima.shared.buildAssembled

/**
 * Служба, которая держит живой канал, пока жив телефон — ПЛАН-УВЕДОМЛЕНИЙ.md, У3.
 *
 * ── ПОЧЕМУ `specialUse`, А НЕ `dataSync` ────────────────────────────────────
 *
 * `dataSync` выглядит подходящим по названию и негоден по двум причинам сразу, и обе
 * наступают, как только `targetSdk` дойдёт до 35:
 *
 * | | `dataSync` | `specialUse` |
 * |---|---|---|
 * | Срок жизни | **6 часов в сутки**, дальше `onTimeout()` и падение | не заявлено |
 * | Запуск из `BOOT_COMPLETED` | **запрещён** | не запрещён |
 *
 * Второе убивает ровно то, ради чего служба и заводится: канал после перезагрузки
 * телефона. Первое означает, что три четверти суток канала нет.
 *
 * Проверку Google Play `specialUse` требует — но нас она не связывает: мы раздаём мимо
 * Play своим APK (ADR-0022). Тот редкий случай, когда самораздача даёт преимущество.
 *
 * ── ПОЧЕМУ СЛУЖБА НЕ СТРОИТ СБОРКУ САМА ─────────────────────────────────────
 *
 * Строит — но через [ChannelHost], который отдаёт **ту же самую** сборку и окну. Второй
 * экземпляр означал бы двух владельцев одной базы: потоки из базы перестали бы
 * обновлять экран, входящее разбиралось бы дважды, а курсор двигали бы оба.
 *
 * ── ЧЕГО СЛУЖБА НЕ ДЕЛАЕТ ───────────────────────────────────────────────────
 *
 * Не показывает сообщений. Показ идёт из `Notices` в общем коде — служба лишь даёт
 * процессу право жить. Её собственная строка говорит одно: приложение на связи.
 */
class ChannelService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        BackgroundWatch.serviceStopped()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Строка переднего плана ставится ПЕРВОЙ и немедленно: Android даёт на это
        // несколько секунд, и опоздание — не «служба без строки», а падение.
        raise()
        // Без намерения служба приходит только в одном случае: система убила процесс и
        // подняла её заново (`START_STICKY`). Это и отличает смерть от тишины (ВЗ0в).
        BackgroundWatch.serviceStarted(bySystem = intent == null)
        val holding = hold()
        // Нет аккаунта — держать нечего, и висеть строкой в шторке не за что.
        if (!holding) {
            stopSelf()
            return START_NOT_STICKY
        }
        // `START_STICKY`: система убила процесс под нехватку памяти — пусть поднимет
        // службу заново. Намерения при этом не будет, и это правильно: всё, что нужно,
        // служба берёт у `Entry` сама.
        return START_STICKY
    }

    /**
     * Взять канал, если он ещё не взят.
     *
     * @return `false` — аккаунта на устройстве нет, держать нечего.
     */
    private fun hold(): Boolean {
        if (ChannelHost.holding()) return true
        val entry = Entry.create(Platform.Android)
        val device = entry.created() ?: return false
        val assembled = ChannelHost.assembled(device.session.deviceId) {
            buildAssembled(
                entry = entry,
                device = device,
                build = io.tima.shared.Build(
                    name = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    code = BuildConfig.VERSION_CODE,
                    stream = BuildConfig.TIMA_STREAM,
                ),
                deviceDatabase = { name -> androidDatabase(applicationContext, name) },
                firstAccount = entry.accountList().firstOrNull()?.userId,
            )
        }
        ChannelHost.hold(assembled)
        Journal.note(LogCode.NET_CHANNEL, "служба взяла канал")
        return true
    }

    private fun raise() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                // Тихо и без всплытия: эта строка висит всегда, и звенеть ей не о чем.
                NotificationChannel(CHANNEL, "На связи", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notice = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("TIMA")
            .setContentText("На связи")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTICE, notice)
        }
    }

    companion object {
        private const val CHANNEL = "tima.online"
        private const val NOTICE = 7301

        /**
         * Поднять службу.
         *
         * Зовётся и из окна при запуске, и из приёмника загрузки. Дважды поднятая служба
         * — это одна служба: второй `onStartCommand` увидит, что канал уже взят.
         */
        fun start(context: Context) {
            val intent = Intent(context, ChannelService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                // С Android 12 службу переднего плана нельзя поднять из фона, и отказ
                // здесь законен — например, при попытке поднять её из фонового
                // намерения. Падать из-за этого нельзя: канал поднимется при открытии
                // окна, а запись в журнале объяснит разницу.
                Journal.trouble(LogCode.NET_CHANNEL, "службу не удалось поднять", "почему" to it.message.orEmpty())
            }
        }
    }
}
